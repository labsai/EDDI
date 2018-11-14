import { IBot, IPackage } from '../utils/AxiosFunctions';
import * as React from 'react';
import * as renderIf from 'render-if';
import styles from './Package.styles';
import './Package.scss';
import * as moment from 'moment';
import { Component, compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { connect } from 'react-redux';
import { packageSelector } from '../../selectors/PackageSelectors';
import * as Radium from 'radium';
import * as _ from 'lodash';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';
import { ModalEnum } from '../utils/ModalEnum';
import { history } from '../../history';

interface IPrivateProps extends IPublicProps {
  packagePayload: IPackage;
  isLoading: boolean;
  error: Error;
  isLoadingPackageData: boolean;
}

interface IPublicProps {
  packageResource: string;
  bot: IBot;
}

interface IState {
  showModal: boolean;
}

class Package extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      showModal: false,
    };
  }
  async componentDidMount() {
    eddiApiActionDispatchers.fetchPackageAction(this.props.packageResource);
  }

  openViewJsonModal = () => {
    ModalActionDispatchers.showViewJsonModal(this.props.packageResource);
  };

  render() {
    const packageHasNewVersion = this.props.packagePayload
      ? this.props.packagePayload.version <
        this.props.packagePayload.currentVersion
      : false;
    return (
      <div>
        {renderIf(this.props.isLoading)(() => <p>{'Loading Package'}</p>)}
        {renderIf(!this.props.isLoading)(() => (
          <div>
            {renderIf(this.props.error)(() => (
              <p>{'Error: Could not load package'}</p>
            ))}
            {renderIf(
              !this.props.error && _.isEmpty(this.props.packagePayload),
            )(() => <p>{'This package does not exist'}</p>)}
            {renderIf(
              !this.props.error && !_.isEmpty(this.props.packagePayload),
            )(() => (
              <div>
                <button
                  onClick={() =>
                    history.push(
                      `/packageview/${this.props.packagePayload.id}?version=${
                        this.props.packagePayload.version
                      }`,
                    )
                  }
                  style={styles.botPackageButton}>
                  <div
                    className={
                      packageHasNewVersion
                        ? 'botPackageName hasNewVersion'
                        : 'botPackageName'
                    }>
                    {this.props.packagePayload.name ||
                      this.props.packagePayload.id}
                  </div>
                  <div
                    className={
                      packageHasNewVersion
                        ? 'botPackageLastModifiedOn hasNewVersion'
                        : 'botPackageLastModifiedOn'
                    }>
                    {moment(this.props.packagePayload.lastModifiedOn).format(
                      'DD.MM.YYYY',
                    )}
                  </div>
                </button>
                {renderIf(packageHasNewVersion)(() => (
                  <WhiteButton
                    text={'Update'}
                    customStyles={styles.updatePackage}
                    onClick={() =>
                      eddiApiActionDispatchers.updateBotAction(
                        this.props.bot,
                        this.props.packagePayload,
                      )
                    }
                  />
                ))}
              </div>
            ))}
          </div>
        ))}
      </div>
    );
  }
}

const ComposedPackage: Component<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(pure, Radium, connect(packageSelector), setDisplayName('Package'))(Package);

export default ComposedPackage;
